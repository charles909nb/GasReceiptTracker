package org.karuk.gasreceipttracker

import android.app.Activity
import android.content.res.ColorStateList
import android.text.Editable
import android.text.TextWatcher
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Filter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView

/** Native guided search, sharing the same behavior across Finance and receipts.
 * Suggestions are produced from actual stored values; no fictitious date or price
 * is ever offered in a dropdown. All filters update the result list immediately.
 */
class GuidedArchiveSearchPanel(
    private val activity: Activity,
    private val body: LinearLayout,
    private val rows: List<ArchiveSearchRow>,
    private val onResults: (List<ArchiveSearchRow>) -> Unit
) {
    private var criteria = ArchiveSearchCriteria()
    private val details = LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
    private val sortValues=GuidedArchiveSearch.sorts
    private var initialized=false
    private fun dp(v:Int)=TrackerStyle.dp(activity,v)

    init {
        body.addView(TrackerStyle.card(activity,"Find a saved record",
            "Choose what to search. Date and amount dropdowns contain only purchases already recorded."))
        caption("Search by")
        val fields=spinner(GuidedArchiveSearch.fields,0)
        body.addView(fields)
        body.addView(details)
        caption("Sort results")
        val sort=spinner(sortValues,0)
        body.addView(sort)
        body.addView(TrackerStyle.button(activity,"Clear search filters") {
            criteria=ArchiveSearchCriteria(sort=sortValues[sort.selectedItemPosition.coerceAtLeast(0)])
            fields.setSelection(0)
            buildDetails()
            notifyResults()
        })
        sort.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent:AdapterView<*>?) {}
            override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long){
                val selected=sortValues[position]
                if(criteria.sort!=selected){criteria=criteria.copy(sort=selected);notifyResults()}
            }
        }
        fields.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent:AdapterView<*>?) {}
            override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long){
                val selected=GuidedArchiveSearch.fields[position]
                if(criteria.field!=selected){
                    criteria=criteria.copy(field=selected,match=when(selected){
                        "Purchase date"->"Any date"
                        "Amount"->"Near amount (5 dollars)"
                        else->"Exact"
                    },first="",second="")
                    buildDetails()
                    notifyResults()
                }
            }
        }
        initialized=true
        buildDetails()
        notifyResults()
    }
    private fun caption(s:String,into:LinearLayout=body) {
        into.addView(TrackerStyle.text(activity,s,13f,true,TrackerStyle.ink).apply {
            setPadding(0,dp(9),0,dp(5))
        })
    }
    private fun spinner(options:List<String>,position:Int):Spinner = Spinner(activity).apply {
        adapter=ArrayAdapter(activity,android.R.layout.simple_spinner_dropdown_item,options)
        setSelection(position.coerceIn(0,(options.size-1).coerceAtLeast(0)))
        background=TrackerStyle.shape(activity,android.graphics.Color.WHITE,TrackerStyle.line,10)
        minimumHeight=dp(53)
        layoutParams=LinearLayout.LayoutParams(-1,dp(53)).apply{bottomMargin=dp(7)}
    }
    private fun buildDetails(){
        details.removeAllViews()
        when(criteria.field){
            "Purchase date","Amount" -> {
                val modes=if(criteria.field=="Purchase date")GuidedArchiveSearch.dateModes
                    else GuidedArchiveSearch.amountModes
                caption("Match",details)
                val choice=spinner(modes,modes.indexOf(criteria.match).coerceAtLeast(0))
                details.addView(choice)
                val fields=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL}
                details.addView(fields)
                fun rebuildInputs(){
                    fields.removeAllViews()
                    val mode=criteria.match
                    if(mode.startsWith("Any ")){
                        val values=GuidedArchiveSearch.choices(rows,criteria.field)
                        fields.addView(TrackerStyle.text(activity,
                            "Showing all records · ${values.size} recorded ${if(criteria.field=="Amount")"amounts" else "dates"} available",
                            12f,false,TrackerStyle.muted))
                        return
                    }
                    val captionText=if(criteria.field=="Purchase date") "recorded date" else "saved amount"
                    caption(when {
                        mode.startsWith("Near") -> "Find records near this $captionText"
                        mode.startsWith("Between") -> "From $captionText (inclusive)"
                        else -> "Choose an exact $captionText"
                    },fields)
                    fields.addView(dropdown(criteria.field,criteria.first){value->
                        criteria=criteria.copy(first=value);notifyResults()
                    })
                    if(mode.startsWith("Between")){
                        caption("Through $captionText (inclusive)",fields)
                        fields.addView(dropdown(criteria.field,criteria.second){value->
                            criteria=criteria.copy(second=value);notifyResults()
                        })
                    }
                    fields.addView(TrackerStyle.text(activity,when(mode){
                        "Near amount (5 dollars)"->"For example, entering $60 displays purchases from $55 to $65 only. Suggestions are actual saved amounts."
                        "Near date (7 days)"->"Shows purchases within seven calendar days before or after the selected date."
                        "Between amounts","Between dates"->"Choose both endpoints from recorded values, or leave one blank for an open-ended range."
                        else ->"Only values that exist in your saved purchases are suggested."
                    },12f,false,TrackerStyle.muted).apply{setPadding(0,dp(7),0,dp(7))})
                }
                choice.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent:AdapterView<*>?) {}
                    override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long){
                        val next=modes[position]
                        if(criteria.match!=next){criteria=criteria.copy(match=next,first="",second="")}
                        rebuildInputs();notifyResults()
                    }
                }
                rebuildInputs()
            }
            "Vehicle","Status" -> {
                caption("Choose ${criteria.field.lowercase()}",details)
                val choices=GuidedArchiveSearch.choices(rows,criteria.field)
                val options=listOf("All "+(if(criteria.field=="Status")"statuses" else "vehicles"))+choices
                val view=spinner(options,options.indexOf(criteria.first).coerceAtLeast(0))
                details.addView(view)
                view.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{
                    override fun onNothingSelected(parent:AdapterView<*>?) {}
                    override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long){
                        val value=if(position==0)"" else options[position]
                        if(criteria.first!=value){criteria=criteria.copy(first=value);notifyResults()}
                    }
                }
            }
            "PR number" -> {
                caption("Choose a recorded request number",details)
                details.addView(dropdown("PR number",criteria.first){value->
                    criteria=criteria.copy(first=value);notifyResults()
                })
            }
            else -> details.addView(TrackerStyle.text(activity,"All saved records",14f,false,TrackerStyle.muted))
        }
    }
    private fun dropdown(field:String,current:String,onChange:(String)->Unit):AutoCompleteTextView {
        val edit=AutoCompleteTextView(activity).apply{
            threshold=0
            hint=when(field){
                "Purchase date"->"Select an existing date"
                "Amount"->"Type $60 or choose an existing amount"
                else->"Search saved request numbers"
            }
            contentDescription=hint
            setSingleLine(true)
            if(field=="Amount")inputType=InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            TrackerStyle.input(activity,this)
        }
        val adapter=object:ArrayAdapter<String>(activity,
            android.R.layout.simple_dropdown_item_1line,mutableListOf<String>()){
            private val searchFilter=object:Filter(){
                override fun performFiltering(constraint:CharSequence?):FilterResults {
                    val found=GuidedArchiveSearch.suggestions(rows,field,constraint?.toString().orEmpty())
                    return FilterResults().apply{values=found;count=found.size}
                }
                override fun publishResults(constraint:CharSequence?,results:FilterResults){
                    @Suppress("UNCHECKED_CAST")
                    val found=results.values as? List<String> ?:emptyList()
                    clear();addAll(found);notifyDataSetChanged()
                }
            }
            override fun getFilter():Filter=searchFilter
        }
        edit.setAdapter(adapter)
        edit.setText(current,false)
        edit.setOnClickListener{edit.showDropDown()}
        edit.setOnFocusChangeListener{_,focused->if(focused)edit.post{edit.showDropDown()}}
        edit.addTextChangedListener(object:TextWatcher{
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){onChange(s?.toString().orEmpty())}
            override fun afterTextChanged(s:Editable?){}
        })
        return edit
    }
    private fun notifyResults(){
        if(!initialized)return
        onResults(GuidedArchiveSearch.results(rows,criteria))
    }
}